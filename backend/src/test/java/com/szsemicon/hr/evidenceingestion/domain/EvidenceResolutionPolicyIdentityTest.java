package com.szsemicon.hr.evidenceingestion.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceResolutionPolicyIdentityTest {

    private static final String DIGEST = "a".repeat(64);
    private static final Instant AT = Instant.parse("2026-08-03T00:17:00Z");

    @Test
    void confirmedSnowflakeBindingWinsOverWrongDeviceEmpno() {
        var pengWei = resolution("employee-peng-wei");
        var zhaoYixian = resolution("employee-zhao-yixian");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZST0289".equals(employeeNumber)
                        ? List.of(zhaoYixian)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return "939805188834107393".equals(externalPersonRef)
                        ? List.of(pengWei)
                        : List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZST0289",
                null,
                "device-1",
                "939805188834107393",
                ConfirmedBindingKind.DELI_USER_ID,
                AT);

        assertThat(decision.status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.MATCHED);
        assertThat(decision.reason()).isEqualTo("CONFIRMED_BINDING");
        assertThat(decision.employeeId()).isEqualTo("employee-peng-wei");
    }

    @Test
    void uniqueEmpnoMatchesWhenSnowflakeIsUnbound() {
        var luYulei = resolution("employee-lu-yulei");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZST0284".equals(employeeNumber)
                        ? List.of(luYulei)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZST0284",
                null,
                "device-1",
                "932303205680513024",
                ConfirmedBindingKind.DELI_USER_ID,
                AT);

        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
        assertThat(decision.employeeId()).isEqualTo("employee-lu-yulei");
        assertThat(decision.companyId()).isEqualTo("company-jn");
    }

    @Test
    void multipleConfirmedBindingsQuarantineThePunch() {
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return List.of(resolution("employee-a"));
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of(
                        resolution("employee-a"),
                        resolution("employee-b"));
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZST0284",
                null,
                "device-1",
                "932303205680513024",
                ConfirmedBindingKind.DELI_USER_ID,
                AT);

        assertThat(decision.status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.AMBIGUOUS);
        assertThat(decision.reason()).isEqualTo("CONFIRMED_BINDING_MULTIPLE");
        assertThat(decision.employeeId()).isNull();
    }

    @Test
    void shortCheckinUserIdWithoutBindingKeepsZhouBuxinEmpno() {
        var zhouBuxin = resolution("employee-zhou-buxin");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZJN0002".equals(employeeNumber)
                        ? List.of(zhouBuxin)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZJN0002",
                null,
                "device-1",
                "387",
                ConfirmedBindingKind.DELI_USER_ID,
                AT);

        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
        assertThat(decision.employeeId()).isEqualTo("employee-zhou-buxin");
    }

    @Test
    void uniqueCheckinMemberNameWinsOverWrongDeviceEmpno() {
        var pengWei = resolution("employee-peng-wei");
        var zhaoYixian = resolution("employee-zhao-yixian");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZST0289".equals(employeeNumber)
                        ? List.of(zhaoYixian)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }

            @Override
            public List<Resolution> resolveByDisplayName(
                    String companyId, String displayName, Instant at) {
                return "彭伟".equals(displayName) ? List.of(pengWei) : List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZST0289",
                null,
                "device-1",
                "218",
                ConfirmedBindingKind.DELI_USER_ID,
                "彭伟",
                AT);

        assertThat(decision.reason()).isEqualTo("NAME_OVER_DEVICE_EMPNO");
        assertThat(decision.employeeId()).isEqualTo("employee-peng-wei");
    }

    @Test
    void duplicateDisplayNameFallsBackToEmpno() {
        var zhaoYixian = resolution("employee-zhao-yixian");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return List.of(zhaoYixian);
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }

            @Override
            public List<Resolution> resolveByDisplayName(
                    String companyId, String displayName, Instant at) {
                return List.of(
                        resolution("employee-a"),
                        resolution("employee-b"));
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZST0289",
                null,
                "device-1",
                "218",
                ConfirmedBindingKind.DELI_USER_ID,
                "重名",
                AT);

        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
        assertThat(decision.employeeId()).isEqualTo("employee-zhao-yixian");
    }

    @Test
    void unlistedDeviceEmpnoWithUniqueRosterNameMatchesByName() {
        var zhangGuoqing = resolution("employee-zhang-guoqing");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }

            @Override
            public List<Resolution> resolveByDisplayName(
                    String companyId, String displayName, Instant at) {
                return "张国庆".equals(displayName)
                        ? List.of(zhangGuoqing)
                        : List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-deli",
                "company-1",
                "SZSTSX61",
                null,
                "device-1",
                "1240000000000000001",
                ConfirmedBindingKind.DELI_USER_ID,
                "张国庆",
                AT);

        assertThat(decision.reason()).isEqualTo("DISPLAY_NAME");
        assertThat(decision.employeeId()).isEqualTo("employee-zhang-guoqing");
    }

    @Test
    void internEmpnoMapsToRosterNumberWhenNameIsMissing() {
        var zhaoJianhao = resolution("employee-zhao-jianhao");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZST0680".equals(employeeNumber)
                        ? List.of(zhaoJianhao)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-oa",
                "company-1",
                "SZSTSX71",
                null,
                null,
                "member-1",
                null,
                AT);

        assertThat(decision.status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.MATCHED);
        assertThat(decision.reason()).isEqualTo("EMPLOYEE_NUMBER");
        assertThat(decision.employeeId()).isEqualTo("employee-zhao-jianhao");
        assertThat(decision.companyId()).isEqualTo("company-jn");
    }

    @Test
    void szt0687AliasesToSzst0687WhenOriginalNumberMisses() {
        var luJiajun = resolution("employee-lu-jiajun");
        var resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String companyId, String employeeNumber, Instant at) {
                return "SZST0687".equals(employeeNumber)
                        ? List.of(luJiajun)
                        : List.of();
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String sourceId,
                    String companyId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return List.of();
            }
        };

        var decision = EvidenceResolutionPolicy.resolve(
                resolver,
                "source-oa",
                "company-1",
                "SZT0687",
                null,
                null,
                null,
                null,
                AT);

        assertThat(decision.status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.MATCHED);
        assertThat(decision.employeeId()).isEqualTo("employee-lu-jiajun");
    }

    @Test
    void doesNotSpecialCaseZhangHailanNameOverEmpno() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/szsemicon/hr/evidenceingestion/domain/"
                        + "EvidenceResolutionPolicy.java"));
        assertThat(source).doesNotContain("张海兰");
        assertThat(source).doesNotContain("SZST0302");
        assertThat(source).doesNotContain("SZST0303");
    }

    private static EmployeeEmploymentResolverPort.Resolution resolution(
            String employeeId) {
        return new EmployeeEmploymentResolverPort.Resolution(
                employeeId, "employment-" + employeeId, DIGEST, "company-jn");
    }
}

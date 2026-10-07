package com.szsemicon.hr.payroll.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class PayrollReservationArchitectureTest {

    private static final String PAYROLL_PACKAGE =
            "com.szsemicon.hr.payroll..";

    @Test
    void noNonPayrollPackageDependsOnOptionalPayrollBranch() {
        var classes = new ClassFileImporter().importPackages("com.szsemicon.hr");

        noClasses()
                .that().resideOutsideOfPackage(PAYROLL_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage(PAYROLL_PACKAGE)
                .check(classes);
    }

    @Test
    void payrollLayersRemainInwardOnlyAndHaveNoRestSurface() {
        var classes = new ClassFileImporter()
                .importPackages("com.szsemicon.hr.payroll");

        noClasses()
                .that().resideInAPackage("..payroll.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "..interfaces..")
                .check(classes);
        noClasses()
                .that().resideInAPackage("..payroll.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "..interfaces..")
                .check(classes);
        assertThat(classes)
                .extracting(JavaClass::getPackageName)
                .noneMatch(name -> name.startsWith(
                        "com.szsemicon.hr.payroll.interfaces"));
    }

    @Test
    void w5SnapshotPortHasNoImplementationBeforeW5Final() {
        var classes = new ClassFileImporter()
                .importPackages("com.szsemicon.hr.payroll");
        String portName = FrozenAttendanceSnapshotPort.class.getName();

        assertThat(classes)
                .filteredOn(javaClass -> !javaClass.isInterface())
                .noneMatch(javaClass -> javaClass.getAllRawInterfaces().stream()
                        .map(JavaClass::getName)
                        .anyMatch(portName::equals));
    }
}

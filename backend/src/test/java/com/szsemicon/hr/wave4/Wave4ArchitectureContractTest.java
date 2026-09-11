package com.szsemicon.hr.wave4;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class Wave4ArchitectureContractTest {

    @Test
    void domainDoesNotDependOnTransportVendorExcelOrW5Types() {
        var classes = new ClassFileImporter().importPackages("com.szsemicon.hr");
        noClasses().that()
                .resideInAnyPackage(
                        "..evidenceingestion.domain..",
                        "..punchimport.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.apache.poi..",
                        "jakarta.servlet..",
                        "..interfaces.rest..",
                        "..infrastructure.synthetic..",
                        "..dailyresult..",
                        "..exceptionhandling..",
                        "..periodclose..")
                .check(classes);
    }

    @Test
    void sourceAdaptersCannotExposeWriteBackOrOrganizationSyncCommands() {
        var classes = new ClassFileImporter().importPackages(
                "com.szsemicon.hr.evidenceingestion");
        noClasses().that().resideInAPackage("..infrastructure.synthetic..")
                .should().haveSimpleNameContaining("WriteBack")
                .orShould().haveSimpleNameContaining("OrganizationSync")
                .orShould().haveSimpleNameContaining("BiometricTemplate")
                .check(classes);
    }
}

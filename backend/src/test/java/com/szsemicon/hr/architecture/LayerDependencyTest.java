package com.szsemicon.hr.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class LayerDependencyTest {

    @Test
    void domainDoesNotDependOnOuterLayers() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.szsemicon.hr");

        noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "..interfaces..")
                .check(classes);
    }

    @Test
    void applicationDoesNotDependOnAdapters() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.szsemicon.hr");

        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "..interfaces..")
                .check(classes);
    }
}

package com.szsemicon.hr.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class LayerDependencyTest {

    @Test
    void domainDoesNotDependOnOuterLayers() {
        var classes = new ClassFileImporter().importPackages("com.szsemicon.hr");

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
        var classes = new ClassFileImporter().importPackages("com.szsemicon.hr");

        noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..infrastructure..",
                        "..interfaces..")
                .check(classes);
    }
}

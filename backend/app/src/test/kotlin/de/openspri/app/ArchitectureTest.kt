package de.openspri.app

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Architecture rules from CLAUDE.md (principle 3): the domain core stays protocol- and framework-neutral
 * so that a future FIT adapter can reuse it.
 */
@AnalyzeClasses(packages = ["de.openspri"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    @ArchTest
    val domainIsFrameworkFree: ArchRule = noClasses()
        .that().resideInAPackage("de.openspri.domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..",
            "jakarta..",
            "tools.jackson..",
            "com.fasterxml..",
            "org.apache.cxf..",
            "io.swagger..",
            "java.sql..",
        )
        .because("the domain core must not depend on frameworks (CLAUDE.md, principle 3)")

    @ArchTest
    val domainDoesNotKnowAdapters: ArchRule = noClasses()
        .that().resideInAPackage("de.openspri.domain..")
        .should().dependOnClassesThat().resideOutsideOfPackages("de.openspri.domain..", "java..", "kotlin..", "org.jetbrains..")
        .because("adapters depend on the domain, never the other way round")
}

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

    /**
     * springdoc names a schema after the simple class name: two DTOs with the same name end up as one schema, and one
     * endpoint is documented with the wrong fields (found with InventoryController.ContractDto, M7).
     */
    @ArchTest
    fun restSchemaNamesAreUnique(classes: com.tngtech.archunit.core.domain.JavaClasses) {
        val schemas = classes
            .filter { it.packageName.startsWith("de.openspri.rest") && it.isAnnotatedWith(io.swagger.v3.oas.annotations.media.Schema::class.java) }
            .groupBy { c -> c.getAnnotationOfType(io.swagger.v3.oas.annotations.media.Schema::class.java).name.ifEmpty { c.simpleName } }
            .filterValues { it.size > 1 }
        kotlin.test.assertEquals(emptyMap(), schemas.mapValues { (_, v) -> v.map { it.name } }, "OpenAPI schema names must be unique")
    }
}

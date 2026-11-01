// Spring Boot assembly: wires all modules into one executable (container image "api").
plugins {
    id("openspri.spring-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation(project(":rest-api"))
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.archunit.junit5)
}

springBoot {
    mainClass = "de.openspri.app.OpenSpriApplicationKt"
    buildInfo()
}

tasks.bootJar {
    archiveFileName = "open-spri.jar"
}

tasks.jar {
    enabled = false
}

tasks.test {
    // ./gradlew :app:test -PupdateOpenApi rewrites docs/api/openapi.json instead of comparing it.
    systemProperty("openspri.updateOpenApi", providers.gradleProperty("updateOpenApi").isPresent)
    systemProperty("openspri.repoRoot", rootDir.absolutePath)
    inputs.files(rootProject.file("docs/api/openapi.json")).withPropertyName("openApiSpec")
}

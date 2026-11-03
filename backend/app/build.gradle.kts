// Spring Boot assembly: wires all modules into one executable (container image "api").
plugins {
    id("openspri.spring-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":spri-soap"))
    implementation(project(":persistence"))
    implementation(project(":rest-api"))
    implementation(project(":auth-token"))
    implementation(project(":auth-local"))
    implementation(project(":simulator"))           // OPENSPRI_MODE=SIMULATOR (container spri-simulator)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.micrometer.registry.prometheus)  // GET /metrics (NF-11)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.archunit.junit5)
    testImplementation(testFixtures(project(":spri-soap")))
    testImplementation(project(":replikat"))
}

springBoot {
    mainClass = "de.openspri.app.OpenSpriApplicationKt"
    // without the build time: the same sources give the same jar (reproducible builds, NF-13)
    buildInfo { excludes = setOf("time") }
}

tasks.bootJar {
    archiveFileName = "open-spri.jar"
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.jar {
    enabled = false
}

tasks.test {
    // Self-test AG ↔ LE (two instances, all KFT test cases): nightly with -Pselftest; load test (AUFGABE 10.1 item 7)
    // with -Pload. Neither is part of every build.
    useJUnitPlatform {
        val special = listOf("selftest", "load").filter { providers.gradleProperty(it).isPresent }
        if (special.isNotEmpty()) includeTags(*special.toTypedArray()) else excludeTags("selftest", "load")
    }
    testLogging { if (providers.gradleProperty("load").isPresent) showStandardStreams = true }
    // ./gradlew :app:test -PupdateOpenApi rewrites docs/api/openapi.json instead of comparing it.
    systemProperty("openspri.updateOpenApi", providers.gradleProperty("updateOpenApi").isPresent)
    systemProperty("openspri.repoRoot", rootDir.absolutePath)
    inputs.files(rootProject.file("docs/api/openapi.json")).withPropertyName("openApiSpec")
    // KFT scenario tests (M5): the test case list is downloaded by :simulator
    val testCases = project(":simulator").tasks.named<de.openspri.build.VerifiedDownload>("downloadKftTestCases")
    dependsOn(testCases)
    systemProperty("openspri.kft.testCases", testCases.get().outputFile.get().asFile.absolutePath)
}

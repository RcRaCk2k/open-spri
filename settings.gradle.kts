rootProject.name = "open-spri"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK toolchain (JDK 25) automatically if it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Backend modules live under backend/<name>; see docs/AUFGABE-SPRI.md, chapter 8.2.
// Further modules (spri-schema, spri-soap, outbox, replikat, auth-api, auth-local, auth-token, setup, simulator)
// are added with their milestones.
listOf("domain", "persistence", "rest-api", "app").forEach { name ->
    include(name)
    project(":$name").projectDir = file("backend/$name")
}

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
// Further modules (replikat, setup) are added with their milestones.
listOf("domain", "spri-schema", "spri-rules", "spri-soap", "auth-api", "auth-token", "auth-local", "persistence", "rest-api", "simulator", "app").forEach { name ->
    include(name)
    project(":$name").projectDir = file("backend/$name")
}

import de.openspri.build.VerifiedDownload

// Protocol-neutral domain core. Must not depend on any framework (enforced by ArchitectureTest in :app).
plugins {
    id("openspri.kotlin-conventions")
}

dependencies {
    testImplementation(libs.poi.ooxml)
}

// KFT test case list of the AK S&P (23.05.2025). The tests replay every expected message sequence through
// the order lifecycle. Downloaded and checksum-verified, not part of the repository (OP-02).
val downloadKftTestCases = tasks.register<VerifiedDownload>("downloadKftTestCases") {
    url = "https://ak-spri.de/wp-content/uploads/Testfaelle_SPRIv4X_20250523.xlsx"
    sha256 = "b7b3e1c7b492fc659430569b6fdd968bca257fe8a61f498c0c0b672e4c2b27d8"
    localCopyDirectory = rootProject.layout.projectDirectory.dir("docs/spec-original")
    cacheDirectory = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/open-spri") })
    outputFile = layout.buildDirectory.file("ak-test-resources/ak/Testfaelle_SPRIv4X.xlsx")
}

sourceSets {
    test {
        resources.srcDir(layout.buildDirectory.dir("ak-test-resources"))
    }
}

tasks.processTestResources { dependsOn(downloadKftTestCases) }

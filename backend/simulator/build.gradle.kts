import de.openspri.build.VerifiedDownload

// KFT-like simulator (AUFGABE 10.3): plays the counterpart of a test case of the KFT test case list over real,
// signed SOAP and drives our instance through its public REST API. Used by the scenario tests in :app and,
// later, as container "spri-simulator".
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(project(":spri-soap"))
    implementation(libs.poi.ooxml)
    implementation("org.slf4j:slf4j-api")
    api("tools.jackson.core:jackson-databind")
}

// KFT test case list of the AK S&P (23.05.2025). Downloaded and checksum-verified, never part of the
// repository or of an image (OP-02); the simulator reads it from a path (tests: system property).
val downloadKftTestCases = tasks.register<VerifiedDownload>("downloadKftTestCases") {
    url = "https://ak-spri.de/wp-content/uploads/Testfaelle_SPRIv4X_20250523.xlsx"
    sha256 = "b7b3e1c7b492fc659430569b6fdd968bca257fe8a61f498c0c0b672e4c2b27d8"
    localCopyDirectory = rootProject.layout.projectDirectory.dir("docs/spec-original")
    cacheDirectory = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/open-spri") })
    outputFile = layout.buildDirectory.file("ak/Testfaelle_SPRIv4X.xlsx")
}

tasks.test {
    dependsOn(downloadKftTestCases)
    systemProperty("openspri.kft.testCases", downloadKftTestCases.get().outputFile.get().asFile.absolutePath)
    systemProperty("openspri.repoRoot", rootDir.absolutePath)
}

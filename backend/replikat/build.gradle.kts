import de.openspri.build.VerifiedDownload

// Replikat (AK S&P "Replikate zur Verfügbarkeitsprüfung" 1.7, AUFGABE 5.4): Replikat.xsd, JAXB classes, reading and
// writing as stream, meta file, delta logic. The annexes are downloaded at build time and verified by checksum;
// they are not part of the repository (copyright of the AK S&P, OP-02).
plugins {
    id("openspri.kotlin-conventions")
    `java-library`
}

val xjc = configurations.create("xjc")

dependencies {
    api(project(":domain"))
    api(platform(libs.spring.boot.bom))
    api("tools.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
    xjc(platform(libs.spring.boot.bom))
    xjc(libs.jaxb.xjc)
    xjc(libs.jaxb.runtime)
    api(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
}

val downloadAnnexes = tasks.register<VerifiedDownload>("downloadReplikatAnnexes") {
    url = "https://ak-spri.de/wp-content/uploads/Anlagen_Replikat_v1.7.zip"
    sha256 = "fa2b3b72bc9a95c4a055893c00c21aa0f41e35f4213abe552af5aa8a9fadac39"
    localCopyDirectory = rootProject.layout.projectDirectory.dir("docs/spec-original")
    cacheDirectory = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/open-spri") })
    outputFile = layout.buildDirectory.file("downloads/Anlagen_Replikat_v1.7.zip")
}

// Replikat.xsd as classpath resource replikat/v1_7/Replikat.xsd (schema validation at runtime)
val unpackXsd = tasks.register<Sync>("unpackReplikatXsd") {
    from(downloadAnnexes.map { zipTree(it.outputFile) }) {
        include("**/Replikat.xsd")
        eachFile { path = "replikat/v1_7/Replikat.xsd" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/replikat-resources"))
}

// Example files of Anlage 3 as test resources replikat-examples/<name>.xml (file names normalized to ASCII)
val unpackExamples = tasks.register<Sync>("unpackReplikatExamples") {
    from(downloadAnnexes.map { zipTree(it.outputFile) }) {
        include("**/Anlage3_*/*.xml")
        // bsp_Konnektivität_20260116.xml has a non-UTF-8 name in the archive
        eachFile { path = "replikat-examples/" + name.substringAfter("bsp_").substringBefore('_').let { if (it.startsWith("Konnektivit")) "Konnektivitaet" else it } + ".xml" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/replikat-examples"))
}

val generateJaxb = tasks.register<JavaExec>("generateReplikatJaxb") {
    val outputDir = layout.buildDirectory.dir("generated/jaxb")
    val schemaDir = unpackXsd.map { it.destinationDir }
    inputs.dir(schemaDir).withPropertyName("schemas")
    outputs.dir(outputDir).withPropertyName("sources")
    classpath = xjc
    mainClass = "com.sun.tools.xjc.XJCFacade"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-d", outputDir.get().asFile.absolutePath,
            "-p", "de.openspri.replikat.jaxb",
            "-encoding", "UTF-8",
            "-npa", "-no-header", "-quiet",
            schemaDir.get().resolve("replikat/v1_7/Replikat.xsd").absolutePath,
        )
    })
    doFirst { outputDir.get().asFile.mkdirs() }
}

sourceSets {
    main {
        java.srcDir(generateJaxb)
        resources.srcDir(layout.buildDirectory.dir("generated/replikat-resources"))
    }
    test {
        resources.srcDir(layout.buildDirectory.dir("generated/replikat-examples"))
    }
}

tasks.processResources { dependsOn(unpackXsd) }
tasks.processTestResources { dependsOn(unpackExamples) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    testImplementation(testFixtures(project(":domain")))
}

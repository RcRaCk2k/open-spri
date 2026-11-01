import de.openspri.build.VerifiedDownload

// Attribute rules from Anlage 1 (fachliches Schema der Auftrags-/Meldungsstruktur), specification 10.4 (4)/(5).
// - source set "generator": build-time tool that reads Anlage 1 (xlsx) and maps every row to an XML path
//   via the generated JAXB classes; not part of the jar.
// - main: the generated rule table (anlage1-rules.tsv) and the validator.
plugins {
    id("openspri.kotlin-conventions")
}

val generator: SourceSet = sourceSets.create("generator")

dependencies {
    "generatorImplementation"(project(":spri-schema"))
    "generatorImplementation"(libs.poi.ooxml)
    "generatorImplementation"(platform(libs.spring.boot.bom))
    testImplementation(project(":spri-schema"))
    testImplementation(testFixtures(project(":spri-soap")))
}

val downloadAnlage1 = tasks.register<VerifiedDownload>("downloadAnlage1") {
    url = "https://ak-spri.de/wp-content/uploads/Anlage_1_AuftragsMeldugsstruktur_SPRI_V.4.4.xlsx"
    sha256 = "fcb70c0966159f90ffbfb1849d102ad42b3f3a19a68a8c0f6c417e3da3052f99"
    localCopyDirectory = rootProject.layout.projectDirectory.dir("docs/spec-original")
    cacheDirectory = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/open-spri") })
    outputFile = layout.buildDirectory.file("downloads/Anlage_1_SPRI_V4.4.xlsx")
}

val generatedRules = layout.buildDirectory.dir("generated/anlage1")

val generateAnlage1Rules = tasks.register<JavaExec>("generateAnlage1Rules") {
    description = "Generates the attribute rules from Anlage 1 (see src/generator/resources/anlage1-mapping.txt)"
    classpath = generator.runtimeClasspath
    mainClass = "de.openspri.rules.generator.Anlage1GeneratorKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs("-Dlog4j2.statusLoggerLevel=OFF") // POI logs via Log4j API; no provider needed for the generator
    val input = downloadAnlage1.flatMap { it.outputFile }
    val output = generatedRules.map { it.file("spri/v4/anlage1-rules.tsv") }
    inputs.file(input).withPropertyName("anlage1")
    inputs.files(generator.runtimeClasspath).withPropertyName("generatorClasspath")
    outputs.file(output).withPropertyName("rules")
    argumentProviders.add(CommandLineArgumentProvider { listOf(input.get().asFile.absolutePath, output.get().asFile.absolutePath) })
}

sourceSets.main {
    resources.srcDir(generatedRules)
}

tasks.processResources { dependsOn(generateAnlage1Rules) }

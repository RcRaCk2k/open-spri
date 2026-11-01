import de.openspri.build.VerifiedDownload

// S/PRI XML schema (Anlage 3, XSD 4.00.03) and the JAXB classes generated from it.
// The XSD files are downloaded at build time and verified by checksum, they are not part of the
// repository (copyright of the AK S&P, open point OP-02).
plugins {
    id("openspri.kotlin-conventions")
    `java-library`
}

val xjc = configurations.create("xjc")

dependencies {
    xjc(platform(libs.spring.boot.bom))
    xjc(libs.jaxb.xjc)
    xjc(libs.jaxb.runtime)
    api(libs.jakarta.xml.bind.api)
    runtimeOnly(libs.jaxb.runtime)
}

val downloadXsd = tasks.register<VerifiedDownload>("downloadXsd") {
    url = "https://ak-spri.de/wp-content/uploads/Anlage_3_XSD_SPRI_V.4.x.zip"
    sha256 = "e54d404771248a917b4df4a8df21f93e235a29b15dc771495851435b9b200f46"
    localCopyDirectory = rootProject.layout.projectDirectory.dir("docs/spec-original")
    cacheDirectory = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/open-spri") })
    outputFile = layout.buildDirectory.file("downloads/Anlage_3_XSD_SPRI_V.4.x.zip")
}

// XSD/WSDL as classpath resources under spri/v4/ (used for runtime schema validation).
val unpackXsd = tasks.register<Sync>("unpackXsd") {
    from(downloadXsd.map { zipTree(it.outputFile) }) {
        include("xsd/**", "wsdl/**")
    }
    into(layout.buildDirectory.dir("generated/xsd-resources/spri/v4"))
}

val generateJaxb = tasks.register<JavaExec>("generateJaxb") {
    val outputDir = layout.buildDirectory.dir("generated/jaxb")
    val schemaDir = unpackXsd.map { it.destinationDir }
    inputs.dir(schemaDir).withPropertyName("schemas")
    outputs.dir(outputDir).withPropertyName("sources")
    classpath = xjc
    mainClass = "com.sun.tools.xjc.XJCFacade"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    // Packages are derived from the XSD namespaces (http://spri.telekom.de/oss/v4/<x> -> de.telekom.spri.oss.v4.<x>),
    // so the generated code maps 1:1 to the specification.
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-d", outputDir.get().asFile.absolutePath,
            "-encoding", "UTF-8",
            "-npa", "-no-header", "-quiet",
            schemaDir.get().resolve("wsdl/oss-envelope.xsd").absolutePath,
        )
    })
    doFirst { outputDir.get().asFile.mkdirs() }
}

sourceSets {
    main {
        java.srcDir(generateJaxb)
        resources.srcDir(layout.buildDirectory.dir("generated/xsd-resources"))
    }
}

tasks.processResources { dependsOn(unpackXsd) }

// Generated code: no warnings-as-errors for javac.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// S/PRI SOAP adapter: WS-Security (WSS4J), schema validation, inbound check pipeline with TEQ,
// outbound client. Apache CXF provides the SOAP 1.1 transport (endpoint, WSDL, HTTP client).
plugins {
    id("openspri.spring-conventions")
    `java-test-fixtures`
}

dependencies {
    api(project(":domain"))
    api(project(":spri-schema"))
    implementation(libs.cxf.frontend.jaxws)
    implementation(libs.cxf.transports.http)
    runtimeOnly(libs.saaj.impl)                  // SAAJ for CXF in JAX-WS MESSAGE mode
    // WSS4J (DOM) with Apache Santuario. SAML support is not needed and OpenSAML is not on Maven Central.
    implementation(libs.wss4j.dom) {
        exclude(group = "org.opensaml")
    }
    implementation(libs.bcpkix)                   // self-signed signing certificates (setup, tests)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot")
    implementation(libs.cxf.spring.boot.starter.jaxws)
    implementation("org.slf4j:slf4j-api")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")

    testImplementation(libs.kotlin.reflect)
    // Test keys, sample messages and in-memory ports, shared with the end-to-end tests in :app.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.bcpkix)
}

// Public REST API (/api/v1). The frontend uses exclusively this API (principle 4 in CLAUDE.md).
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    implementation(project(":domain"))
    api(project(":auth-api"))
    implementation(project(":spri-soap"))
    implementation(project(":replikat"))
    implementation(project(":auth-token"))
    implementation(project(":auth-local"))
    api(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    api(libs.springdoc.webmvc.ui)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
}

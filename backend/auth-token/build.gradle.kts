// Authentication module "api-token": long-life tokens for external systems (AUFGABE 7.5).
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(project(":auth-api"))
    implementation("org.springframework:spring-web")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
}

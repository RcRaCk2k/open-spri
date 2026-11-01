// Database access (Spring JdbcClient, plain SQL) and Flyway migrations (MariaDB 11).
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(project(":domain"))
    implementation(project(":auth-token"))
    implementation(project(":auth-local"))
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mariadb.jdbc)
}

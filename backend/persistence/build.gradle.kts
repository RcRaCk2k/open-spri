// Database access and Flyway migrations (MariaDB 11). Repositories follow from M2 onwards.
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(libs.spring.boot.starter.jdbc)
    api(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.mysql)
    runtimeOnly(libs.mariadb.jdbc)
}

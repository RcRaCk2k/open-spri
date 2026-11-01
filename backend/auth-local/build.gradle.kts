// Authentication module "local-users": user name and password (Argon2id), session for the web frontend.
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(project(":auth-api"))
    implementation(libs.bcpkix) // Argon2 of Spring Security uses Bouncy Castle
}

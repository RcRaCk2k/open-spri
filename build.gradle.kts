// Root project: configuration lives in build-logic (convention plugins) and the module build files.
plugins {
    // Load the Spring Boot plugin once for all subprojects (avoids duplicate Kotlin plugin class loaders).
    alias(libs.plugins.spring.boot) apply false
}

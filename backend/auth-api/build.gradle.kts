// Authentication SPI (AUFGABE chapter 7.5): roles, scopes, the acting principal and the module interface.
// Further methods (OIDC, SAML, LDAP) are added as modules implementing AuthenticationModule.
plugins {
    id("openspri.spring-conventions")
}

dependencies {
    api(libs.spring.boot.starter.security)
}

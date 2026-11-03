package de.openspri.rest.auth

import de.openspri.auth.ApiTokenModule
import de.openspri.auth.ApiTokenStore
import de.openspri.auth.ApiTokens
import de.openspri.auth.AuthenticationModule
import de.openspri.auth.LocalUsers
import de.openspri.auth.Scope
import de.openspri.auth.UserStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * Security of the REST API (AUFGABE 7.3, 7.5).
 *
 * - Authentication by the [AuthenticationModule] beans: "api-token" (Bearer) and "local-users" (session
 *   cookie, SameSite=Strict). New methods (OIDC, SAML) are additional modules; nothing here changes.
 * - CSRF protection for cookie sessions (SPA mode: cookie XSRF-TOKEN, header X-XSRF-TOKEN); requests with a
 *   Bearer token carry no cookies and are exempt.
 * - Authorization by scopes (`SCOPE_…`) on every endpoint (@PreAuthorize).
 * - The S/PRI endpoint (path /spri) has its own protection (IP allowlist and WS-Security signature) and is not touched here.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    @Bean
    fun apiTokens(store: ApiTokenStore) = ApiTokens(store)

    @Bean
    fun apiTokenModule(tokens: ApiTokens, store: ApiTokenStore) = ApiTokenModule(tokens, store)

    @Bean
    fun localUsers(store: UserStore) = LocalUsers(store)

    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()

    @Bean
    fun securityFilterChain(http: HttpSecurity, modules: List<AuthenticationModule>, contexts: SecurityContextRepository): SecurityFilterChain {
        modules.forEach { it.configure(http) }
        val bearer = RequestMatcher { request -> request.getHeader("Authorization")?.startsWith("Bearer ") == true }
        http
            .securityContext { it.securityContextRepository(contexts) }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .csrf { csrf ->
                csrf.spa()
                csrf.ignoringRequestMatchers(bearer)
                csrf.ignoringRequestMatchers("/spri/**", "/replikat/**", "/api/v1/auth/login", "/api/v1/setup/**")
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(*PUBLIC).permitAll()
                auth.requestMatchers("/metrics").hasAuthority(Scope.METRICS_READ.authority.authority)
                auth.anyRequest().authenticated()
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
        return http.build()
    }

    companion object {
        /** Reachable without login. Partner access (/spri, /replikat) is protected by its own checks. */
        val PUBLIC = arrayOf(
            "/error", "/health", "/health/**", "/api/v1/about", "/api/v1/openapi.json", "/api/v1/openapi.json/**",
            "/api/docs", "/api/docs/**", "/swagger-ui/**",
            "/api/v1/auth/login", "/api/v1/auth/modules", "/api/v1/setup", "/api/v1/setup/**",
            "/spri/**", "/replikat/**",
        )
    }
}

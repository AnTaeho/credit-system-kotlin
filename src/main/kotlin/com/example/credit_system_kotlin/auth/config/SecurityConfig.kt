package com.example.credit_system_kotlin.auth.config

import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.auth.web.AuthCookies
import com.example.credit_system_kotlin.auth.web.SecurityErrorWriter
import com.example.credit_system_kotlin.auth.web.TokenApiController
import com.example.credit_system_kotlin.auth.web.TokenAuthenticationFilter
import com.example.credit_system_kotlin.auth.web.TokenLogoutHandler
import com.example.credit_system_kotlin.user.repository.UserRepository
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.savedrequest.NullRequestCache
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.AnyRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authProperties: AuthProperties,
        accessTokenService: AccessTokenService,
        refreshTokenService: RefreshTokenService,
        userRepository: UserRepository,
        cookies: AuthCookies,
        errorWriter: SecurityErrorWriter,
        environment: Environment
    ): SecurityFilterChain {
        val paths = PathPatternRequestMatcher.withDefaults()
        val api: RequestMatcher = paths.matcher("/api/**")
        val tokenEndpoint: RequestMatcher = paths.matcher(HttpMethod.POST, TokenApiController.PATH)
        val managementPortSeparated = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT

        http {
            authorizeHttpRequests {
                if (managementPortSeparated) {
                    authorize(EndpointRequest.to("health", "prometheus"), permitAll)
                }
                authorize(EndpointRequest.toAnyEndpoint(), denyAll)
                authorize("/error", permitAll)
                authorize("/login", permitAll)
                authorize("/signup", permitAll)
                authorize(tokenEndpoint, permitAll)
                authorize("/css/**", permitAll)
                authorize("/js/**", permitAll)
                authorize("/favicon.ico", permitAll)
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize("/admin", hasRole("ADMIN"))
                authorize("/admin/**", hasRole("ADMIN"))
                authorize("/api/**", authenticated)
                authorize(anyRequest, authenticated)
            }
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            requestCache {
                requestCache = NullRequestCache()
            }
            logout {
                logoutUrl = "/logout"
                addLogoutHandler(TokenLogoutHandler(refreshTokenService, cookies))
                logoutSuccessUrl = "/login?logout"
            }
            csrf {
                csrfTokenRepository = csrfTokenRepository(authProperties)
                ignoringRequestMatchers(TokenAuthenticationFilter.BEARER_REQUEST, tokenEndpoint)
            }
            headers {
                contentSecurityPolicy {
                    policyDirectives = CONTENT_SECURITY_POLICY
                }
            }
            exceptionHandling {
                defaultAuthenticationEntryPointFor(errorWriter.unauthenticatedEntryPoint(), api)
                defaultAuthenticationEntryPointFor(
                    LoginUrlAuthenticationEntryPoint("/login"),
                    AnyRequestMatcher.INSTANCE
                )
                defaultAccessDeniedHandlerFor(errorWriter.forbiddenHandler(), api)
            }
            addFilterBefore<AnonymousAuthenticationFilter>(
                TokenAuthenticationFilter(accessTokenService, refreshTokenService, userRepository, cookies)
            )
        }
        return http.build()
    }

    private fun csrfTokenRepository(authProperties: AuthProperties): CookieCsrfTokenRepository =
        CookieCsrfTokenRepository().apply {
            setCookieCustomizer { cookie ->
                cookie.sameSite(AuthCookies.SAME_SITE)
                if (authProperties.cookieSecure) {
                    cookie.secure(true)
                }
            }
        }

    companion object {
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
                "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
    }
}

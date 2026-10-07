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

/** 보안 규칙은 전부 여기 있다. 세션이 없고(STATELESS) 인증은 요청마다 [TokenAuthenticationFilter] 가 한다. */
@Configuration
@EnableConfigurationProperties(AuthProperties::class)
class SecurityConfig {

    /**
     * 관리 포트가 따로면 부트가 이 체인을 거기에도 건다. 그때만 health·prometheus 를 열고, 포트가 같으면 액추에이터를 다 막는다.
     * CSRF 는 Bearer 요청과 `POST /auth/token` 만 뺀다. 둘 다 브라우저가 붙여 주는 쿠키 인증을 쓰지 않는다.
     */
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
                // 이 둘을 닫으면 로그인 화면이 다시 로그인을 요구해 리다이렉트 루프가 된다.
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
                // 앞에 적은 것이 먼저 맞는다. /api 는 JSON, 나머지는 로그인 화면.
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

    /**
     * CSRF 토큰을 쿠키에 둔다. 기본값대로 `HttpOnly` 다 — 스크립트는 쿠키가 아니라 서버가 그려 준 meta 에서 읽는다.
     * 토큰은 화면이 처음 읽을 때 만들어져 그때 쿠키가 심긴다.
     */
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
        /**
         * 스크립트·스타일은 `static/js`, `static/css` 의 같은 출처 파일만. 인라인은 막는다(XSS 가 새도 실행되지 않게).
         * 이미지도 같은 출처만 — 생성 결과 URL 은 아직 이미지로 그리지 않는다.
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
                "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
    }
}

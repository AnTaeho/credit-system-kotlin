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

/**
 * 보안 규칙 한 곳.
 *
 * **누구인가.** 세션이 없다(`STATELESS`). 요청마다 [TokenAuthenticationFilter] 가 액세스 JWT(`Authorization: Bearer`
 * 헤더, 없으면 `credit_at` 쿠키)로 사용자를 정하고, 액세스가 만료됐으면 `credit_rt` 쿠키의 리프레시 토큰으로 조용히
 * 갱신한다. 어떤 요청도 `JSESSIONID` 를 만들지 않는다. 로그인 전에 가려던 주소를 기억해 두는 요청 캐시도 세션을
 * 쓰므로 끈다 — 로그인하면 항상 홈으로 간다.
 *
 * **어디에 들어갈 수 있는가.**
 * - `/api` 아래: 인증 필요. 미인증 401, 권한 부족·CSRF 실패 403. 둘 다 [SecurityErrorWriter] 의 JSON 이고 리다이렉트하지 않는다
 * - `/api/admin` 아래: 운영자(ROLE_ADMIN) 전용. 운영자 경로는 전부 이 아래에 둔다
 * - `/admin` 화면: 운영자 전용(API 와 같은 기준)
 * - 그 밖의 경로(화면): 인증 필요. 미인증이면 `/login` 화면으로 보낸다
 * - 열어 두는 곳: `/login`, `/signup`, `POST /auth/token`, 정적 리소스. 로그인·가입 화면을 열어 두지 않으면
 *   로그인 화면이 다시 로그인을 요구하는 리다이렉트 루프가 된다
 * - CSP: 같은 출처의 스크립트·스타일만 허용한다. 인라인 스크립트·스타일·외부 CDN 을 쓰지 않는다
 *
 * **CSRF.** 브라우저는 쿠키로 인증하므로 켠다. 세션이 없어 토큰도 쿠키(`XSRF-TOKEN`)에 둔다. 화면은 서버가 그려 준
 * meta·hidden 값으로 토큰을 돌려보내고, 서버는 그 값이 쿠키와 같은지 본다. 빼는 요청은 둘뿐이다.
 * - Bearer 헤더가 붙은 요청: 브라우저가 스스로 붙여 주는 인증이 아니다. 이런 요청은 필터가 쿠키를 아예 보지
 *   않으므로([TokenAuthenticationFilter.BEARER_REQUEST]) 헤더만 붙여 CSRF 검사를 건너뛰고 쿠키로 인증될 길이 없다
 * - `POST /auth/token`: 비밀번호를 직접 내는 요청이라 따라갈 쿠키 인증이 없고, 쿠키를 심지도 않는다
 *
 * `POST /login`, `POST /signup`, `POST /logout` 은 빼지 않는다. 폼의 hidden `_csrf` 가 있어야 들어온다.
 *
 * **로그아웃.** `POST /logout`. 리프레시 사슬을 폐기하고 쿠키를 지운 뒤 `/login?logout` 으로 보낸다([TokenLogoutHandler]).
 *
 * **액추에이터.** 관리 포트를 따로 두면 부트가 이 필터 체인을 관리 포트의 자식 컨텍스트에도 그대로 건다
 * (`ServletManagementChildContextConfiguration`). 그래서 여기서 두 경우를 나눈다.
 * - 관리 포트가 따로일 때: 관리 포트로 들어온 health·prometheus 는 인증 없이 통과한다. 스크레이프와
 *   헬스체크는 사람이 아니다. 관리 포트는 compose 네트워크 밖으로 publish 하지 않는 것이 경계다.
 *   애플리케이션 포트에는 액추에이터가 아예 없다(404 이전에 인증 요구에 걸린다).
 * - 관리 포트가 같을 때(관리 포트를 따로 안 준 기동): 액추에이터가 공개 포트에 섞여 있으므로 전부 막는다.
 *   `EndpointRequest` 는 포트가 갈라져 있으면 관리 컨텍스트로 온 요청에만 맞는다.
 */
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
         * 이미지도 같은 출처만 — 생성 결과 URL 은 아직 이미지로 그리지 않는다(step12).
         */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; " +
                "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"
    }
}

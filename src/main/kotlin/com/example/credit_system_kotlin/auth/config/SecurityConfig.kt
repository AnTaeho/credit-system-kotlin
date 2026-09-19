package com.example.credit_system_kotlin.auth.config

import com.example.credit_system_kotlin.auth.login.AllowlistOidcUserService
import com.example.credit_system_kotlin.auth.login.DevLoginFilter
import com.example.credit_system_kotlin.auth.login.UserAccountProvisioner
import com.example.credit_system_kotlin.auth.web.SecurityErrorWriter
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher

/**
 * 보안 규칙 한 곳.
 *
 * - `/api` 아래: 인증 필요. 미인증 401, 권한 부족·CSRF 실패 403. 둘 다 [SecurityErrorWriter] 의 JSON 이고 리다이렉트하지 않는다
 * - `/api/admin` 아래: 운영자(ROLE_ADMIN) 전용. 운영자 경로는 전부 이 아래에 둔다
 * - 그 밖의 경로: 인증 필요. 미인증이면 구글 로그인으로 보낸다
 * - CSRF: 세션 쿠키로 인증하므로 켠다. 개발 로그인 헤더로 인증되는 요청만 뺀다(쿠키 인증이 아니다)
 * - 로그아웃: `POST /logout`
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
        oidcUserService: AllowlistOidcUserService,
        provisioner: UserAccountProvisioner,
        errorWriter: SecurityErrorWriter,
        environment: Environment
    ): SecurityFilterChain {
        val api: RequestMatcher = PathPatternRequestMatcher.withDefaults().matcher("/api/**")
        val devLoginHeader = RequestHeaderRequestMatcher(DevLoginFilter.HEADER)
        val managementPortSeparated = ManagementPortType.get(environment) == ManagementPortType.DIFFERENT

        http {
            authorizeHttpRequests {
                if (managementPortSeparated) {
                    authorize(EndpointRequest.to("health", "prometheus"), permitAll)
                }
                authorize(EndpointRequest.toAnyEndpoint(), denyAll)
                authorize("/error", permitAll)
                authorize("/api/admin/**", hasRole("ADMIN"))
                authorize("/api/**", authenticated)
                authorize(anyRequest, authenticated)
            }
            oauth2Login {
                userInfoEndpoint {
                    this.oidcUserService = oidcUserService
                }
            }
            logout {
                logoutUrl = "/logout"
            }
            csrf {
                if (authProperties.devLogin.enabled) {
                    ignoringRequestMatchers(devLoginHeader)
                }
            }
            exceptionHandling {
                defaultAuthenticationEntryPointFor(errorWriter.unauthenticatedEntryPoint(), api)
                defaultAccessDeniedHandlerFor(errorWriter.forbiddenHandler(), api)
            }
            if (authProperties.devLogin.enabled) {
                addFilterBefore<AnonymousAuthenticationFilter>(DevLoginFilter(authProperties, provisioner, errorWriter))
            }
        }
        return http.build()
    }
}

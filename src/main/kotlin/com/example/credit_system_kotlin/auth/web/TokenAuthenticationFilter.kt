package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.login.TokenUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
import com.example.credit_system_kotlin.auth.token.AccessPrincipal
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import com.example.credit_system_kotlin.auth.token.RotationResult
import com.example.credit_system_kotlin.user.domain.UserRole
import com.example.credit_system_kotlin.user.repository.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

private val log = LoggerFactory.getLogger(TokenAuthenticationFilter::class.java)

/**
 * 요청에 실린 토큰으로 "누구인가"를 정한다. 세션은 없다. 인증은 **이 요청 하나에만** 걸린다.
 *
 * 1. `Authorization: Bearer <액세스 JWT>` 가 있으면 그것만 본다. 무효면 미인증이다.
 *    **쿠키로 넘어가지 않는다.** Bearer 요청은 CSRF 검사에서 빠지므로(SecurityConfig), 헤더만 아무렇게나 붙이고
 *    쿠키로 인증되는 길이 있으면 다른 사이트가 그 길로 CSRF 검사를 건너뛴다.
 * 2. 없으면 `credit_at` 쿠키의 액세스 JWT 를 본다.
 * 3. 액세스가 없거나 무효(대개 만료)이고 `credit_rt` 쿠키가 있으면 **조용히 갱신한다.** 리프레시를 회전해
 *    새 액세스를 내고 쿠키로 다시 심는다. 사용자는 15분마다 로그인하지 않는다.
 *    - 이 요청이 회전을 차지했으면 두 쿠키를 모두 새로 심는다.
 *    - 동시에 날아간 다른 요청이 먼저 차지했으면(유예 안) 액세스만 심는다. 새 리프레시는 이긴 요청의 응답에 실려 있다.
 *    - 재사용 탐지·폐기·만료·모르는 토큰이거나 사용자 행이 없으면 두 쿠키를 지우고 미인증으로 둔다.
 * 4. 정적 리소스에서는 갱신하지 않는다. 화면 하나가 css·js 를 같이 부르면서 회전을 여러 번 일으키지 않게 한다.
 *
 * 액세스가 유효한 요청은 DB 를 보지 않는다. 역할은 토큰에 들어 있다. 갱신할 때만 사용자 행에서 역할을 다시 읽으므로,
 * 역할이 바뀌면 늦어도 액세스 수명 뒤에 반영된다.
 *
 * 인증하지 못해도 여기서 응답을 끝내지 않는다. 미인증으로 흘려보내면 SecurityConfig 의 규칙이 401·리다이렉트를 정한다.
 *
 * 빈으로 등록하지 않는다. `@Component` 필터는 서블릿 컨테이너에도 자동 등록돼 보안 체인 밖에서 한 번 더 돈다.
 * SecurityConfig 가 만들어 체인에 끼운다.
 */
class TokenAuthenticationFilter(
    private val accessTokenService: AccessTokenService,
    private val refreshTokenService: RefreshTokenService,
    private val userRepository: UserRepository,
    private val cookies: AuthCookies
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val principal = resolve(request, response)
        if (principal != null) {
            val context = SecurityContextHolder.createEmptyContext()
            context.authentication = UsernamePasswordAuthenticationToken.authenticated(
                TokenUser(principal.userId),
                null,
                authoritiesFor(principal.role)
            )
            SecurityContextHolder.setContext(context)
        }
        chain.doFilter(request, response)
    }

    private fun resolve(request: HttpServletRequest, response: HttpServletResponse): AccessPrincipal? {
        if (BEARER_REQUEST.matches(request)) {
            val token = request.getHeader(HttpHeaders.AUTHORIZATION).substring(BEARER_PREFIX.length).trim()
            return token.takeIf { it.isNotEmpty() }?.let(accessTokenService::verify)
        }
        cookies.readAccess(request)?.let(accessTokenService::verify)?.let { return it }

        val refreshRaw = cookies.readRefresh(request) ?: return null
        if (STATIC_RESOURCE.matches(request)) {
            return null
        }
        return refreshSilently(refreshRaw, response)
    }

    private fun refreshSilently(refreshRaw: String, response: HttpServletResponse): AccessPrincipal? {
        val result = refreshTokenService.rotate(refreshRaw)
        val principal = when (result) {
            is RotationResult.Rotated -> principalOf(result.userId)?.also {
                cookies.writeAccess(response, accessTokenService.issue(it.userId, it.role))
                cookies.writeRefresh(response, result.newRaw)
            }

            is RotationResult.WithinGrace -> principalOf(result.userId)?.also {
                cookies.writeAccess(response, accessTokenService.issue(it.userId, it.role))
            }

            RotationResult.ReuseDetected, RotationResult.Rejected -> null
        }
        if (principal == null) {
            // 못 쓰는 쿠키를 남겨 두면 요청마다 다시 회전을 시도한다.
            cookies.clear(response)
        }
        return principal
    }

    private fun principalOf(userId: Long): AccessPrincipal? {
        val role: UserRole? = userRepository.findById(userId).map { it.role }.orElse(null)
        if (role == null) {
            log.warn("리프레시 토큰은 유효하지만 사용자 행이 없음: userId={}", userId)
            return null
        }
        return AccessPrincipal(userId, role)
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "

        /**
         * Bearer 헤더가 붙은 요청. 이 필터가 "쿠키를 보지 않는" 조건과 SecurityConfig 가 "CSRF 검사를 빼는" 조건이
         * 같은 판정이어야 하므로 한 곳에 둔다. 둘이 어긋나면 그 틈이 CSRF 구멍이다.
         */
        val BEARER_REQUEST = RequestMatcher { request ->
            request.getHeader(HttpHeaders.AUTHORIZATION)?.startsWith(BEARER_PREFIX, ignoreCase = true) == true
        }

        private val STATIC_RESOURCE: RequestMatcher = PathPatternRequestMatcher.withDefaults().let {
            OrRequestMatcher(it.matcher("/css/**"), it.matcher("/js/**"), it.matcher("/favicon.ico"))
        }
    }
}

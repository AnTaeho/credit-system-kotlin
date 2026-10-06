package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.login.TokenUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
import com.example.credit_system_kotlin.auth.token.AccessPrincipal
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshTokenService
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
 * 요청에 실린 토큰으로 사용자를 정한다. 인증은 이 요청 하나에만 걸리고, 못 해도 응답을 끝내지 않고 흘려보낸다.
 * 빈으로 등록하지 않는다. `@Component` 필터는 서블릿 컨테이너에도 등록돼 보안 체인 밖에서 한 번 더 돈다.
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

    /**
     * Bearer 헤더가 있으면 그것만 보고, 무효여도 쿠키로 넘어가지 않는다([BEARER_REQUEST]).
     * 정적 리소스 요청은 리프레시로 갱신하지 않는다. 갱신하면 화면 하나가 부르는 css·js 요청마다 DB 를 본다.
     */
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

    /** 리프레시가 유효하면 액세스 쿠키만 새로 심는다. 리프레시 쿠키는 그대로 둔다. 그 밖에는 쿠키를 지우고 미인증이다. */
    private fun refreshSilently(refreshRaw: String, response: HttpServletResponse): AccessPrincipal? {
        val principal = refreshTokenService.userIdOf(refreshRaw)?.let(::principalOf)
        if (principal == null) {
            // 못 쓰는 쿠키를 남겨 두면 요청마다 다시 갱신을 시도한다.
            cookies.clear(response)
            return null
        }
        cookies.writeAccess(response, accessTokenService.issue(principal.userId, principal.role))
        return principal
    }

    /** 갱신할 때만 사용자 행에서 역할을 다시 읽는다. 액세스가 유효한 요청은 DB 를 보지 않는다. */
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

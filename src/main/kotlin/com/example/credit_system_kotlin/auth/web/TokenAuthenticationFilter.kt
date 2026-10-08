package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.login.TokenUser
import com.example.credit_system_kotlin.auth.login.authoritiesFor
import com.example.credit_system_kotlin.auth.token.AccessPrincipal
import com.example.credit_system_kotlin.auth.token.AccessTokenService
import com.example.credit_system_kotlin.auth.token.RefreshCheck
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

    // 리프레시가 무효면 쿠키를 지운다. Redis 를 못 봐서 확인할 수 없으면 쿠키를 남겨 Redis 가 돌아온 뒤 이어지게 한다.
    private fun refreshSilently(refreshRaw: String, response: HttpServletResponse): AccessPrincipal? {
        val principal = when (val check = refreshTokenService.check(refreshRaw)) {
            is RefreshCheck.Valid -> principalOf(check.userId)
            RefreshCheck.Invalid -> null
            RefreshCheck.Unavailable -> return null
        }
        if (principal == null) {
            cookies.clear(response)
            return null
        }
        cookies.writeAccess(response, accessTokenService.issue(principal.userId, principal.role))
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

        val BEARER_REQUEST = RequestMatcher { request ->
            request.getHeader(HttpHeaders.AUTHORIZATION)?.startsWith(BEARER_PREFIX, ignoreCase = true) == true
        }

        private val STATIC_RESOURCE: RequestMatcher = PathPatternRequestMatcher.withDefaults().let {
            OrRequestMatcher(it.matcher("/css/**"), it.matcher("/js/**"), it.matcher("/favicon.ico"))
        }
    }
}

package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.config.AuthProperties
import com.example.credit_system_kotlin.auth.login.DevLoginAuthenticator
import com.example.credit_system_kotlin.auth.login.UserAccountProvisioner
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 로컬 브라우저용 개발 로그인. `/login` 화면의 폼이 `POST /dev-login` 으로 이메일을 보내면 그 사람으로 **세션에** 로그인한다.
 *
 * - 판정(허용 목록·권한)은 헤더 개발 로그인과 같은 [DevLoginAuthenticator] 다.
 * - 이 경로는 세션 쿠키 인증을 만들어 내므로 CSRF 면제 대상이 아니다. 폼의 hidden `_csrf` 가 있어야 들어온다.
 * - 로그인 성공 시 구글 로그인과 같이 세션 id 를 바꾸고(세션 고정 방지) CSRF 토큰을 새로 만든다.
 * - `app.auth.dev-login.enabled=true` 일 때만 빈이 된다. 꺼져 있으면 `/dev-login` 에 핸들러가 없어 404 다.
 *   prod 에서 켜는 것은 [com.example.credit_system_kotlin.auth.config.AuthStartupGuard] 가 기동 단계에서 막는다.
 */
@Controller
@ConditionalOnProperty(prefix = "app.auth.dev-login", name = ["enabled"], havingValue = "true")
class DevSessionLoginController(
    authProperties: AuthProperties,
    provisioner: UserAccountProvisioner
) {

    private val authenticator = DevLoginAuthenticator(authProperties, provisioner)
    private val contextRepository = HttpSessionSecurityContextRepository()
    private val sessionStrategy = CompositeSessionAuthenticationStrategy(
        listOf(
            ChangeSessionIdAuthenticationStrategy(),
            CsrfAuthenticationStrategy(HttpSessionCsrfTokenRepository())
        )
    )

    @PostMapping("/dev-login")
    fun login(
        @RequestParam(required = false) email: String?,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): String {
        val authentication = email
            ?.takeIf { it.isNotBlank() }
            ?.let { authenticator.authenticate(it, channel = "session") }
            ?: return "redirect:/login?error"

        sessionStrategy.onAuthentication(authentication, request, response)
        val holder = SecurityContextHolder.getContextHolderStrategy()
        val context = holder.createEmptyContext()
        context.authentication = authentication
        holder.context = context
        contextRepository.saveContext(context, request, response)
        return "redirect:/"
    }
}

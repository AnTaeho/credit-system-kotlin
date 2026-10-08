package com.example.credit_system_kotlin.auth.web

import com.example.credit_system_kotlin.auth.token.RefreshTokenService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler

class TokenLogoutHandler(
    private val refreshTokenService: RefreshTokenService,
    private val cookies: AuthCookies
) : LogoutHandler {

    override fun logout(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication?) {
        cookies.readRefresh(request)?.let(refreshTokenService::delete)
        cookies.clear(response)
    }
}
